package com.auction.auction.service;

import com.auction.auction.domain.Product;
import com.auction.auction.dto.CreateProductRequest;
import com.auction.auction.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public Product createProduct(Long sellerId, CreateProductRequest request) {
        if (request.getStartingPrice().signum() <= 0) {
            throw new IllegalArgumentException("시작가는 0보다 커야 합니다.");
        }

        Product product = new Product(
                sellerId,
                request.getName(),
                request.getDescription(),
                request.getStartingPrice()
        );
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Product getProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "상품을 찾을 수 없습니다. id=" + productId));
    }

    @Transactional(readOnly = true)
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }
}
